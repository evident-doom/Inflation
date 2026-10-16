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
 * Settled worlds kept ready for short tests: three seeds of the full map (and one small map for checking that code runs), each run
 * for some years and saved, so a test of a change starts from a world past its first years' swings
 * (the herds' slow climb from their start numbers, the plants finding their level) and needs only the
 * years the change itself takes to show. They live in ~/.inflation/test-worlds/NAME: state.bin (the
 * saved world), about.properties (how and when it was made) and settle.csv (the history of its settling).
 *
 * Smaller worlds were tried and are not kept for questions about the animals: on 298e7e7, 400 x 800 km
 * maps (32-40°N only) had 5-7 of about 28 kinds within the full map's seeds' range, with 4-5 kinds
 * missing, and 500 x 2000 km strips matched 10-16 of 31 trends while saving only a quarter of a step's
 * time. small-1 stays for checking that code runs. [compare] remains for setting any other world
 * beside the full map.
 *
 * A settled world still drifts (after 30 years wild boar and mouflon fell about 5% a year), so a change
 * is judged only against a base run from the same start: [ab] runs both on each full map, with the
 * weather drawn several ways, and reports each kind's effect, paired by weather.
 *
 * Usage:
 *   testWorld list
 *   testWorld build NAME... [--years N] [--commit HASH]   (settles each and saves it; the config file's keys apply, as in hexFauna)
 *   testWorld ab --variant FILE [--base FILE] [--worlds full-1,full-2,full-3] [--years 3] [--replicates 2] [--parallel 1] [--out DIR]
 *        (FILE: config keys; the variant's are laid over the base's, the base being the run's own config file unless given)
 *   testWorld compare --full A.csv,B.csv[,..] [--full-variant A2.csv,B2.csv,..] NAME=BASE.csv[,VARIANT.csv] ...
 * A single run on a settled world: hexFauna --world NAME --years N [--replicate R] [--history FILE].
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
        Entry("small-1", 1, 400, 800, "for checking code runs only: 32-40°N, kinds missing"),
        // The full map's seeds again, settled on the rain with fronts fading over land and sea showers (beside
        // full-1..3, which other tests still start from): test on them with --worlds rain-1,rain-2,rain-3.
        Entry("rain-1", 1, 1000, 2000, "the full map, new rain"),
        Entry("rain-2", 2, 1000, 2000, "the full map, new rain"),
        Entry("rain-3", 3, 1000, 2000, "the full map, new rain"),
    )

    /** The worlds a change is tested on. */
    val FULL = ALL.filter { it.name.startsWith("full-") }

    fun named(name: String): Entry = ALL.firstOrNull { it.name == name } ?: error("no test world $name; they are ${ALL.joinToString(", ") { it.name }}")

    /** Says what [e] is and how it was made, and warns where today's code or config differ from when it was settled. */
    fun check(e: Entry, sim: HexSim) {
        require(e.state.isFile) { "test world ${e.name} is not built yet (testWorld build ${e.name})" }
        val a = e.info() ?: Properties()
        println("test world ${e.name}: seed ${e.seed}, ${e.widthKm} x ${e.heightKm} km (${e.note}), ${sim.land.n} places; settled ${a.getProperty("years", "?")} years at commit ${a.getProperty("commit", "?")} on ${a.getProperty("built", "?")}")
        val key = "%016x".format(inflation.world.WorldCache.key(e.config))
        if (a.getProperty("worldKey") != key) println("WARNING: the world-making code has changed since ${e.name} was settled: its saved state is of the old land and climate. Rebuild it (testWorld build ${e.name}) before trusting a test on it.")
        val then = a.getProperty("config", ""); val now = Config.set("")
        if (then != now) println("WARNING: the config differs from when ${e.name} was settled.\n  then: ${then.ifEmpty { "(defaults)" }}\n  now:  ${now.ifEmpty { "(defaults)" }}")
    }

    fun build(e: Entry, years: Int, commit: String = gitCommit()) {
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
        p["years"] = "$years"; p["commit"] = commit; p["built"] = java.time.LocalDateTime.now().withNano(0).toString()
        p["worldKey"] = "%016x".format(inflation.world.WorldCache.key(e.config)); p["config"] = Config.set("")
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
                    val stale = a.getProperty("worldKey") != "%016x".format(inflation.world.WorldCache.key(e.config))
                    "%s years at %s on %s, %s s a year, %.0f MB%s".format(a.getProperty("years"), a.getProperty("commit"), a.getProperty("built"), a.getProperty("secondsPerYear"),
                        e.state.length() / 1e6, if (stale) " — WORLD CODE CHANGED SINCE: rebuild" else "")
                }
            }
            println("%-8s %5d %5d x %4d  %-38s %s".format(e.name, e.seed, e.widthKm, e.heightKm, e.note, built))
        }
    }

    // ---- a change against its base

    /**
     * Runs the base and the variant on each of [worlds] from its settled state, [replicates] times with
     * the weather drawn a different way each time (the same way for the base and the variant), each in a
     * JVM of its own (the config is read once at a JVM's start), [parallel] at a time; then [abReport].
     */
    fun ab(variant: File, base: File?, worlds: List<Entry>, years: Int, replicates: Int, parallel: Int, out: File) {
        out.mkdirs()
        for (w in worlds) require(w.state.isFile) { "test world ${w.name} is not built yet (testWorld build ${w.name})" }
        val baseProps = Properties()
        (base ?: Config.source)?.let { f -> f.reader().use { baseProps.load(it) } }
        val varProps = Properties().also { it.putAll(baseProps) }
        variant.reader().use { varProps.load(it) }
        val baseFile = File(out, "base.properties"); val varFile = File(out, "variant.properties")
        baseFile.writer().use { baseProps.store(it, "base, written by testWorld ab") }
        varFile.writer().use { varProps.store(it, "variant (${variant.path} over the base), written by testWorld ab") }
        val javaBin = ProcessHandle.current().info().command().orElse("java")
        val heap = "-Xmx${Runtime.getRuntime().maxMemory() / (1 shl 20)}m"
        data class Job(val label: String, val cfg: File, val args: List<String>)
        val jobs = worlds.flatMap { w -> (0 until replicates).flatMap { r -> listOf("base" to baseFile, "variant" to varFile).map { (side, cfg) ->
            Job("${w.name}-$side-r$r", cfg, listOf("--world", w.name, "--years", "$years", "--replicate", "$r", "--history", File(out, "${w.name}-$side-r$r.csv").path))
        } } }
        println("testWorld ab: ${jobs.size} runs of $years years into $out, $parallel at a time")
        val pool = java.util.concurrent.Executors.newFixedThreadPool(parallel)
        val failed = java.util.concurrent.ConcurrentLinkedQueue<String>()
        for (j in jobs) pool.submit {
            val t0 = System.nanoTime()
            val p = ProcessBuilder(listOf(javaBin, heap, "-Dhex.config=${j.cfg.absolutePath}", "-Duser.home=${System.getProperty("user.home")}", "-cp", System.getProperty("java.class.path"), "inflation.tools.HexFaunaCheckKt") + j.args)
                .redirectErrorStream(true).redirectOutput(File(out, "${j.label}.txt")).start()
            val code = p.waitFor()
            if (code != 0) failed += j.label
            println("  ${j.label}: ${if (code == 0) "done" else "FAILED (exit $code, see ${j.label}.txt)"} in %.0f s".format((System.nanoTime() - t0) / 1e9))
        }
        pool.shutdown(); pool.awaitTermination(Long.MAX_VALUE, java.util.concurrent.TimeUnit.DAYS)
        require(failed.isEmpty()) { "runs failed: ${failed.joinToString(", ")}" }
        abReport(out, worlds.map { it.name }, replicates, variant.name)
    }

    /**
     * For each key number and world: the variant's effect against its base in the last whole year, %,
     * paired by weather (mean and range over the replicates); how far the base itself swings with the
     * weather (highest against lowest replicate, %); and whether every pair went the same way.
     */
    fun abReport(out: File, worlds: List<String>, replicates: Int, what: String) {
        val runs = worlds.associateWith { w -> (0 until replicates).map { r -> Run(File(out, "$w-base-r$r.csv")) to Run(File(out, "$w-variant-r$r.csv")) } }
        val keys = runs.values.first().first().first.keys
        val lines = ArrayList<String>()
        lines += "effect of $what, % in the last whole year (${runs.values.first().first().first.years.size} years), paired by weather; per world: mean [range over $replicates replicates] / the base's own swing with the weather"
        lines += "%-24s ".format("") + worlds.joinToString(" | ") { "%-32s".format(it) } + " | all pairs"
        var same = 0; var mixed = 0
        for (k in keys) {
            val cells = ArrayList<String>(); val all = ArrayList<Double>()
            for (w in worlds) {
                val pairs = runs.getValue(w)
                val effs = pairs.map { (b, v) -> val a = b.yearly(k, b.years.last()); val c = v.yearly(k, v.years.last()); if (a > 0 && c > 0) 100 * ln(c / a) else Double.NaN }.filter { !it.isNaN() }
                val bases = pairs.map { (b, _) -> b.yearly(k, b.years.last()) }.filter { it > 0 }
                val swing = if (bases.size > 1) 100 * ln(bases.max() / bases.min()) else Double.NaN
                all += effs
                cells += if (effs.isEmpty()) "%-32s".format("-") else "%-32s".format("%s [%s, %s] / %s".format(fmt(effs.average()), fmt(effs.min()), fmt(effs.max()), fmt(swing)))
            }
            val way = when {
                all.isEmpty() -> "-"
                all.all { it > 0 } -> { same++; "all up" }
                all.all { it < 0 } -> { same++; "all down" }
                all.all { it == 0.0 } -> "none"
                else -> { mixed++; "mixed" }
            }
            lines += "%-24s ".format(k.substringAfter('/').take(24)) + cells.joinToString(" | ") + " | " + way
        }
        val n = worlds.size * replicates
        lines += migrationReport(runs, worlds)
        lines += "-> $same key numbers moved the same way in all $n pairs, $mixed were mixed (a mixed one is not shown to change by this test; a number the change does not touch goes one way in all $n pairs by chance %.0f%% of the time)".format(100 * 2 * Math.pow(0.5, n.toDouble()))
        File(out, "ab-report.txt").writeText(lines.joinToString("\n", postfix = "\n"))
        println(lines.joinToString("\n"))
        println("(written to ${File(out, "ab-report.txt")})")
    }

    /**
     * The migratory herds in the last whole year, base -> variant (means over the replicates) with the
     * range of the paired differences: how far their mean height swings over the months (highest month's
     * against lowest, m), how far their share above 1000 m swings (points), and how far a herd stands
     * from where it stood half a year before (km, the year's mean). Empty for runs recorded before these
     * numbers were.
     */
    fun migrationReport(runs: Map<String, List<Pair<Run, Run>>>, worlds: List<String>): List<String> {
        val first = runs.values.first().first().first
        val kinds = first.allKeys.filter { it.startsWith("migration/") }.map { it.split('/')[1] }.distinct()
        if (kinds.isEmpty()) return emptyList()
        val lines = ArrayList<String>()
        lines += ""
        lines += "migration in the last whole year, base -> variant (mean over replicates) [range of the paired differences]"
        lines += "%-34s ".format("") + worlds.joinToString(" | ") { "%-32s".format(it) }
        fun swing(m: DoubleArray) = m.filter { !it.isNaN() }.let { if (it.size < 2) Double.NaN else it.max() - it.min() }
        val measures = listOf<Pair<String, (Run, String) -> Double>>(
            "height swing m" to { r, k -> swing(r.monthly("migration/$k/mean height m", r.years.last())) },
            "above 1000 m swing pts" to { r, k -> 100 * swing(r.monthly("migration/$k/share above 1000 m", r.years.last())) },
            "km from half a year" to { r, k -> r.yearly("migration/$k/km from half a year before", r.years.last()) },
        )
        for (k in kinds) for ((label, f) in measures) {
            val cells = worlds.map { w ->
                val pairs = runs.getValue(w).map { (b, v) -> f(b, k) to f(v, k) }.filter { !it.first.isNaN() && !it.second.isNaN() }
                if (pairs.isEmpty()) "%-32s".format("-") else {
                    val d = pairs.map { it.second - it.first }
                    "%-32s".format("%s -> %s [%s, %s]".format(fmt(pairs.map { it.first }.average()), fmt(pairs.map { it.second }.average()), fmt(d.min()), fmt(d.max())))
                }
            }
            lines += "%-34s ".format("$k, $label".take(34)) + cells.joinToString(" | ")
        }
        return lines
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

        /** Every series recorded. */
        val allKeys: Set<String> get() = h.series.keys
        /** Each month's mean of [key] in [year] (NaN for a month not in it). */
        fun monthly(key: String, year: Int): DoubleArray {
            val s = h.series[key] ?: return DoubleArray(12) { Double.NaN }
            val sum = DoubleArray(12); val c = IntArray(12)
            for (i in rows(year)) { val v = s[i]; if (!v.isNaN()) { val m = h.month[i].toInt(); sum[m] += v; c[m]++ } }
            return DoubleArray(12) { if (c[it] == 0) Double.NaN else sum[it] / c[it] }
        }
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
            var commit: String? = null
            val names = ArrayList<String>()
            var k = 1
            while (k < args.size) { when (args[k]) { "--years" -> years = args[++k].toInt(); "--commit" -> commit = args[++k]; else -> names += args[k] }; k++ }
            require(names.isNotEmpty()) { "build which? ${TestWorlds.ALL.joinToString(", ") { it.name }}" }
            for (n in names) TestWorlds.build(TestWorlds.named(n), years, commit ?: gitCommit())
        }
        "ab" -> {
            var variant: File? = null; var base: File? = null; var worlds = TestWorlds.FULL; var years = 3; var replicates = 2; var parallel = 1
            var out = File("runs/ab-" + java.time.LocalDateTime.now().withNano(0).toString().replace(":", ""))
            var k = 1
            while (k < args.size) {
                when (args[k]) {
                    "--variant" -> variant = File(args[++k]); "--base" -> base = File(args[++k])
                    "--worlds" -> worlds = args[++k].split(',').map { TestWorlds.named(it.trim()) }
                    "--years" -> years = args[++k].toInt(); "--replicates" -> replicates = args[++k].toInt()
                    "--parallel" -> parallel = args[++k].toInt(); "--out" -> out = File(args[++k])
                    else -> error("unknown argument ${args[k]}")
                }
                k++
            }
            TestWorlds.ab(requireNotNull(variant) { "ab needs --variant FILE (config keys of the change)" }, base, worlds, years, replicates, parallel, out)
        }
        "ab-report" -> {
            // ab-report DIR [--worlds a,b] [--replicates N]: the report again from runs already made.
            val out = File(args[1]); var worlds = TestWorlds.FULL.map { it.name }; var replicates = 2
            var k = 2
            while (k < args.size) { when (args[k]) { "--worlds" -> worlds = args[++k].split(','); "--replicates" -> replicates = args[++k].toInt(); else -> error("unknown argument ${args[k]}") }; k++ }
            TestWorlds.abReport(out, worlds, replicates, "the variant")
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
        else -> println("usage: testWorld list | testWorld build NAME... [--years N] [--commit HASH] | testWorld ab --variant FILE [--base FILE] [--worlds full-1,full-2,full-3] [--years 3] [--replicates 2] [--parallel 1] [--out DIR] | testWorld ab-report DIR | testWorld compare --full A.csv,B.csv [--full-variant A2.csv,B2.csv] NAME=BASE.csv[,VARIANT.csv] ...\n" +
            "a test on a settled world: hexFauna --world NAME --years N [--replicate R] [--history FILE]")
    }
}
