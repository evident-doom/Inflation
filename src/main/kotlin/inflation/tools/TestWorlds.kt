package inflation.tools

import inflation.Args
import inflation.Config
import inflation.ecology.HexHistory
import inflation.run.Nominal
import inflation.run.TestWorlds
import inflation.run.gitCommit
import java.io.File
import java.util.Properties

private const val USAGE = "testWorld list | testWorld build NAME... [--years N] [--commit HASH] | testWorld adopt NAME STATE.bin [--years N] [--commit HASH] [--config TEXT] | testWorld ab --variant FILE [--base FILE] [--worlds m5-1,m5-2,m5-3] [--years 3] [--replicates 2] [--parallel 1] [--out DIR] | testWorld ab-report DIR [--worlds a,b] [--replicates 2] | testWorld nominal HISTORY.csv... | testWorld compare --full A.csv,B.csv [--full-variant A2.csv,B2.csv] NAME=BASE.csv[,VARIANT.csv] ...\n" +
    "a test on a settled world: hexFauna --world NAME --years N [--replicate R] [--history FILE]"

/** The testWorld program: see [TestWorlds]. */
fun main(args: Array<String>) {
    val a = Args(args.drop(1).toTypedArray(), USAGE)
    when (args.getOrNull(0)) {
        "list" -> { a.done(); TestWorlds.list() }
        "build" -> {
            val years = a.int("--years", TestWorlds.SETTLE_YEARS)
            val commit = a.string("--commit") ?: gitCommit()
            val names = a.rest(); a.done()
            require(names.isNotEmpty()) { "build which? ${TestWorlds.ALL.joinToString(", ") { it.name }}" }
            for (n in names) build(TestWorlds.named(n), years, commit)
        }
        "adopt" -> {
            val years = a.int("--years", TestWorlds.SETTLE_YEARS)
            val commit = a.string("--commit") ?: gitCommit()
            val config = a.string("--config") ?: ""
            val (name, file) = a.rest().also { require(it.size == 2) { "adopt NAME STATE.bin" } }
            a.done()
            adopt(TestWorlds.named(name), File(file), years, commit, config)
        }
        "ab" -> {
            val variant = requireNotNull(a.file("--variant")) { "ab needs --variant FILE (config keys of the change)" }
            val base = a.file("--base")
            val worlds = a.string("--worlds")?.let { named(it) } ?: TestWorlds.FULL
            val years = a.int("--years", 3); val replicates = a.int("--replicates", 2); val parallel = a.int("--parallel", 1)
            val out = a.file("--out") ?: File("runs/ab-" + java.time.LocalDateTime.now().withNano(0).toString().replace(":", ""))
            a.done()
            TestWorlds.ab(variant, base, worlds, years, replicates, parallel, out)
        }
        "ab-report" -> {
            // The report again from runs already made.
            val worlds = a.string("--worlds")?.split(',') ?: TestWorlds.FULL.map { it.name }
            val replicates = a.int("--replicates", 2)
            val out = File(a.rest().singleOrNull() ?: error("ab-report DIR: which runs?")); a.done()
            TestWorlds.abReport(out, worlds, replicates, "the variant")
        }
        "nominal" -> {
            // nominal HISTORY.csv...: whether runs already made stayed within nominal limits (their series; see Nominal).
            val files = a.rest(); a.done()
            require(files.isNotEmpty()) { "nominal needs history files (.csv)" }
            for (f in files) println("$f: " + Nominal.history(HexHistory.readCsv(File(f))).report(most = Int.MAX_VALUE))
        }
        "compare" -> {
            fun runs(s: String) = s.split(',').filter { it.isNotBlank() }.map { TestWorlds.Run(File(it)) }
            val full = a.string("--full")?.let { runs(it) } ?: emptyList()
            val fullVariant = a.string("--full-variant")?.let { runs(it) } ?: emptyList()
            val tests = a.rest().map { t ->
                require('=' in t) { "unknown argument $t" }
                val (n, files) = t.split('=', limit = 2); val r = runs(files); Triple(n, r[0], r.getOrNull(1))
            }
            a.done()
            TestWorlds.compare(full, fullVariant, tests)
        }
        else -> println("usage: $USAGE")
    }
}

/** The test worlds named in [list], comma-separated. */
private fun named(list: String) = list.split(',').map { TestWorlds.named(it.trim()) }

/** Keeps [state], a save settled elsewhere (same code and seed), as test world [e]; [config] is the config it was settled under, as "key value, ...". */
private fun adopt(e: TestWorlds.Entry, state: File, years: Int, commit: String, config: String) {
    require(state.isFile) { "no save at $state" }
    e.dir.mkdirs()
    state.copyTo(e.state, overwrite = true)
    val p = Properties()
    p["name"] = e.name; p["seed"] = "${e.seed}"; p["widthKm"] = "${e.widthKm}"; p["heightKm"] = "${e.heightKm}"
    p["years"] = "$years"; p["commit"] = commit; p["built"] = java.time.LocalDateTime.now().withNano(0).toString()
    p["worldKey"] = e.worldKey; p["config"] = config
    e.about.writer().use { p.store(it, "test world ${e.name}, adopted by testWorld adopt") }
    println("test world ${e.name} kept from $state: ${e.state} (%.0f MB)".format(e.state.length() / 1e6))
}

/** Settles test world [e] for [years] years and saves it, with its history and how it was made. */
private fun build(e: TestWorlds.Entry, years: Int, commit: String = gitCommit()) {
    e.dir.mkdirs()
    val building = File(e.dir, "building.bin")
    val t0 = System.nanoTime()
    faunaCheck(arrayOf("--seed", "${e.seed}", "--width", "${e.widthKm}", "--height", "${e.heightKm}", "--years", "$years", "--save", building.path, "--history", e.settle.path))
    val seconds = (System.nanoTime() - t0) / 1e9
    if (e.state.exists()) e.state.delete()
    require(building.renameTo(e.state)) { "could not move $building to ${e.state}" }
    val area = HexHistory.readCsv(e.settle).series[HexHistory.Key.LAND_AREA]?.let { it[it.size - 1] } ?: Float.NaN
    val p = Properties()
    p["name"] = e.name; p["seed"] = "${e.seed}"; p["widthKm"] = "${e.widthKm}"; p["heightKm"] = "${e.heightKm}"
    p["years"] = "$years"; p["commit"] = commit; p["built"] = java.time.LocalDateTime.now().withNano(0).toString()
    p["worldKey"] = e.worldKey; p["config"] = Config.set("")
    p["landKm2"] = "%.0f".format(area); p["buildSeconds"] = "%.0f".format(seconds)
    p["secondsPerYear"] = "%.1f".format(seconds / years)
    e.about.writer().use { p.store(it, "test world ${e.name}, written by testWorld build") }
    println("test world ${e.name} settled for $years years in %.0f s (%.1f s a year, with history and saving): ${e.state} (%.0f MB)".format(seconds, seconds / years, e.state.length() / 1e6))
}
